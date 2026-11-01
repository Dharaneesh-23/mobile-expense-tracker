from decimal import Decimal, InvalidOperation
from typing import Literal
from uuid import UUID
from pydantic import BaseModel, Field, EmailStr, field_validator, model_validator

class Credentials(BaseModel):
    email: EmailStr
    password: str = Field(min_length=12, max_length=72)
    @field_validator('password')
    @classmethod
    def bcrypt_limit(cls, value):
        if len(value.encode()) > 72:
            raise ValueError('Password must be at most 72 UTF-8 bytes')
        return value

class RefreshBody(BaseModel):
    refresh_token: str = Field(min_length=32, max_length=512)

class Change(BaseModel):
    id: UUID
    kind: Literal['transaction', 'budget', 'split']
    updated_at: int = Field(ge=0)
    deleted: bool = False
    data: dict
    @model_validator(mode='after')
    def validate_data(self):
        import json
        if len(json.dumps(self.data)) > 16000:
            raise ValueError('Record too large')
        if self.deleted:
            return self
        try:
            amount = Decimal(str(self.data['amount']))
            if not amount.is_finite() or amount <= 0 or amount > Decimal('9999999999.99') or amount.as_tuple().exponent < -2:
                raise ValueError()
        except (KeyError, InvalidOperation, ValueError):
            raise ValueError('Positive amount with at most two decimals required') from None
        self.data['amount'] = str(amount.quantize(Decimal('0.01')))
        if self.kind == 'transaction':
            if self.data.get('direction') not in ['debit', 'credit'] or not str(self.data.get('payee', '')).strip():
                raise ValueError('Transaction requires direction and payee')
            if not isinstance(self.data.get('occurred_at'), int) or not 0 <= self.data['occurred_at'] <= 32503680000000:
                raise ValueError('Valid occurred_at timestamp required')
        elif self.kind == 'split':
            if self.data.get('direction') not in ['you_owe_them', 'they_owe_you'] or self.data.get('status') not in ['pending', 'settled']:
                raise ValueError('Invalid split direction or status')
            if not self.data.get('group') or not self.data.get('payee'):
                raise ValueError('Split requires a group and person')
        elif not self.data.get('category'):
            raise ValueError('Budget requires category')
        return self

class SyncBody(BaseModel):
    device_id: str = Field(min_length=1, max_length=100)
    cursor: int = Field(default=0, ge=0)
    changes: list[Change] = Field(default_factory=list, max_length=1000)
